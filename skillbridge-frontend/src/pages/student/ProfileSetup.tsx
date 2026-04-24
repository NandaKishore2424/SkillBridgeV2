/**
 * Student Profile Setup Page
 * 
 * Multi-step wizard for completing student profile on first login
 * Displayed when user has account_status = 'PENDING_SETUP'
 */

import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import * as z from 'zod'
import {
    Card,
    CardContent,
    CardDescription,
    CardHeader,
    CardTitle,
    Button,
    Input,
    Label,
    Textarea,
    Alert,
    AlertDescription,
} from '@/shared/components/ui'
import { useToastNotifications } from '@/shared/hooks/useToastNotifications'
import {
    completeProfile,
    type StudentProfileUpdateData,
} from '@/api/student'
import {
    User,
    Phone,
    GraduationCap,
    FileText,
    CheckCircle,
    Loader2,
    ArrowRight,
    ArrowLeft,
    Github,
    Globe,
    FileDown,
} from 'lucide-react'

// Validation schema
const profileSchema = z.object({
    fullName: z.string().min(2, 'Full name must be at least 2 characters'),
    phone: z
        .string()
        .regex(/^[+]?[0-9]{10,15}$/, 'Phone number must be 10-15 digits'),
    degree: z.string().min(2, 'Degree is required'),
    branch: z.string().min(2, 'Branch is required'),
    year: z.coerce.number().int().min(1).max(5, 'Year must be between 1 and 5'),
    rollNumber: z.string().min(1, 'Roll number is required'),
    bio: z.string().optional(),
    githubUrl: z
        .string()
        .regex(
            /^(https?:\/\/)?(www\.)?(github\.com\/)[a-zA-Z0-9_-]+\/?$/i,
            'Must be a valid GitHub profile URL'
        )
        .optional()
        .or(z.literal('')),
    portfolioUrl: z
        .string()
        .regex(/^(https?:\/\/).*$/, 'Must be a valid URL starting with http:// or https://')
        .optional()
        .or(z.literal('')),
    resumeUrl: z
        .string()
        .regex(/^(https?:\/\/).*$/, 'Must be a valid URL starting with http:// or https://')
        .optional()
        .or(z.literal('')),
})

type ProfileFormData = z.infer<typeof profileSchema>

const STEPS = [
    {
        id: 1,
        title: 'Personal Information',
        description: 'Tell us about yourself',
        icon: User,
    },
    {
        id: 2,
        title: 'Academic Details',
        description: 'Your educational background',
        icon: GraduationCap,
    },
    {
        id: 3,
        title: 'Additional Info',
        description: 'Optional profile links',
        icon: FileText,
    },
]

export function ProfileSetup() {
    const navigate = useNavigate()
    const { showSuccess, showError } = useToastNotifications()
    const [currentStep, setCurrentStep] = useState(1)

    const {
        register,
        handleSubmit,
        formState: { errors },
        trigger,
        watch,
    } = useForm<ProfileFormData>({
        resolver: zodResolver(profileSchema),
        mode: 'onChange',
    })

    const completeMutation = useMutation({
        mutationFn: completeProfile,
        onSuccess: () => {
            showSuccess('Profile completed successfully! Welcome to SkillBridge.')
            setTimeout(() => {
                navigate('/student/dashboard')
            }, 1500)
        },
        onError: (error: any) => {
            showError(
                error?.response?.data?.message ||
                'Failed to complete profile. Please try again.'
            )
        },
    })

    const onSubmit = (data: ProfileFormData) => {
        // Clean up empty optional fields
        const cleanData: StudentProfileUpdateData = {
            fullName: data.fullName,
            phone: data.phone,
            degree: data.degree,
            branch: data.branch,
            year: data.year,
            rollNumber: data.rollNumber,
            bio: data.bio || undefined,
            githubUrl: data.githubUrl || undefined,
            portfolioUrl: data.portfolioUrl || undefined,
            resumeUrl: data.resumeUrl || undefined,
        }

        completeMutation.mutate(cleanData)
    }

    const handleNext = async () => {
        let fieldsToValidate: (keyof ProfileFormData)[] = []

        if (currentStep === 1) {
            fieldsToValidate = ['fullName', 'phone']
        } else if (currentStep === 2) {
            fieldsToValidate = ['degree', 'branch', 'year', 'rollNumber']
        }

        const isValid = await trigger(fieldsToValidate)
        if (isValid) {
            setCurrentStep((prev) => prev + 1)
        }
    }

    const handleBack = () => {
        setCurrentStep((prev) => prev - 1)
    }

    return (
        <div className="min-h-screen bg-gradient-to-br from-blue-50 via-white to-purple-50 flex items-center justify-center p-4">
            <Card className="w-full max-w-2xl shadow-xl">
                <CardHeader className="space-y-4">
                    <div className="flex items-center justify-center mb-4">
                        <div className="h-12 w-12 rounded-full bg-primary/10 flex items-center justify-center">
                            <GraduationCap className="h-6 w-6 text-primary" />
                        </div>
                    </div>
                    <div className="text-center">
                        <CardTitle className="text-2xl">Complete Your Profile</CardTitle>
                        <CardDescription>
                            Set up your profile to get started with SkillBridge
                        </CardDescription>
                    </div>

                    {/* Progress Steps */}
                    <div className="flex items-center justify-between pt-6">
                        {STEPS.map((step, index) => {
                            const Icon = step.icon
                            const isActive = currentStep === step.id
                            const isCompleted = currentStep > step.id

                            return (
                                <div key={step.id} className="flex items-center flex-1">
                                    <div className="flex flex-col items-center flex-1">
                                        <div
                                            className={`h-10 w-10 rounded-full flex items-center justify-center border-2 transition-colors ${isCompleted
                                                ? 'bg-primary border-primary text-white'
                                                : isActive
                                                    ? 'border-primary text-primary'
                                                    : 'border-muted text-muted-foreground'
                                                }`}
                                        >
                                            {isCompleted ? (
                                                <CheckCircle className="h-5 w-5" />
                                            ) : (
                                                <Icon className="h-5 w-5" />
                                            )}
                                        </div>
                                        <div className="mt-2 text-center hidden sm:block">
                                            <p
                                                className={`text-xs font-medium ${isActive ? 'text-primary' : 'text-muted-foreground'
                                                    }`}
                                            >
                                                {step.title}
                                            </p>
                                        </div>
                                    </div>
                                    {index < STEPS.length - 1 && (
                                        <div
                                            className={`h-0.5 flex-1 mx-2 ${isCompleted ? 'bg-primary' : 'bg-muted'
                                                }`}
                                        />
                                    )}
                                </div>
                            )
                        })}
                    </div>
                </CardHeader>

                <form onSubmit={handleSubmit(onSubmit)}>
                    <CardContent className="space-y-6">
                        {/* Step 1: Personal Information */}
                        {currentStep === 1 && (
                            <div className="space-y-4 animate-in fade-in duration-300">
                                <div className="space-y-2">
                                    <Label htmlFor="fullName">
                                        Full Name <span className="text-destructive">*</span>
                                    </Label>
                                    <div className="relative">
                                        <User className="absolute left-3 top-3 h-4 w-4 text-muted-foreground" />
                                        <Input
                                            id="fullName"
                                            placeholder="John Doe"
                                            className="pl-10"
                                            {...register('fullName')}
                                        />
                                    </div>
                                    {errors.fullName && (
                                        <p className="text-sm text-destructive">
                                            {errors.fullName.message}
                                        </p>
                                    )}
                                </div>

                                <div className="space-y-2">
                                    <Label htmlFor="phone">
                                        Phone Number <span className="text-destructive">*</span>
                                    </Label>
                                    <div className="relative">
                                        <Phone className="absolute left-3 top-3 h-4 w-4 text-muted-foreground" />
                                        <Input
                                            id="phone"
                                            placeholder="+919876543210"
                                            className="pl-10"
                                            {...register('phone')}
                                        />
                                    </div>
                                    {errors.phone && (
                                        <p className="text-sm text-destructive">
                                            {errors.phone.message}
                                        </p>
                                    )}
                                    <p className="text-xs text-muted-foreground">
                                        Format: +91XXXXXXXXXX or 10-15 digits
                                    </p>
                                </div>
                            </div>
                        )}

                        {/* Step 2: Academic Details */}
                        {currentStep === 2 && (
                            <div className="space-y-4 animate-in fade-in duration-300">
                                <div className="grid grid-cols-2 gap-4">
                                    <div className="space-y-2">
                                        <Label htmlFor="degree">
                                            Degree <span className="text-destructive">*</span>
                                        </Label>
                                        <Input
                                            id="degree"
                                            placeholder="B.Tech"
                                            {...register('degree')}
                                        />
                                        {errors.degree && (
                                            <p className="text-sm text-destructive">
                                                {errors.degree.message}
                                            </p>
                                        )}
                                    </div>

                                    <div className="space-y-2">
                                        <Label htmlFor="branch">
                                            Branch <span className="text-destructive">*</span>
                                        </Label>
                                        <Input
                                            id="branch"
                                            placeholder="Computer Science"
                                            {...register('branch')}
                                        />
                                        {errors.branch && (
                                            <p className="text-sm text-destructive">
                                                {errors.branch.message}
                                            </p>
                                        )}
                                    </div>
                                </div>

                                <div className="grid grid-cols-2 gap-4">
                                    <div className="space-y-2">
                                        <Label htmlFor="year">
                                            Year <span className="text-destructive">*</span>
                                        </Label>
                                        <Input
                                            id="year"
                                            type="number"
                                            min="1"
                                            max="5"
                                            placeholder="3"
                                            {...register('year')}
                                        />
                                        {errors.year && (
                                            <p className="text-sm text-destructive">
                                                {errors.year.message}
                                            </p>
                                        )}
                                    </div>

                                    <div className="space-y-2">
                                        <Label htmlFor="rollNumber">
                                            Roll Number <span className="text-destructive">*</span>
                                        </Label>
                                        <Input
                                            id="rollNumber"
                                            placeholder="CS2022001"
                                            {...register('rollNumber')}
                                        />
                                        {errors.rollNumber && (
                                            <p className="text-sm text-destructive">
                                                {errors.rollNumber.message}
                                            </p>
                                        )}
                                    </div>
                                </div>

                                <div className="space-y-2">
                                    <Label htmlFor="bio">Bio (Optional)</Label>
                                    <Textarea
                                        id="bio"
                                        placeholder="Tell us about yourself, your interests, and career goals..."
                                        rows={4}
                                        {...register('bio')}
                                    />
                                    <p className="text-xs text-muted-foreground">
                                        {watch('bio')?.length || 0} / 1000 characters
                                    </p>
                                </div>
                            </div>
                        )}

                        {/* Step 3: Additional Info */}
                        {currentStep === 3 && (
                            <div className="space-y-4 animate-in fade-in duration-300">
                                <Alert>
                                    <AlertDescription>
                                        These fields are optional but help us personalize your
                                        experience and showcase your work.
                                    </AlertDescription>
                                </Alert>

                                <div className="space-y-2">
                                    <Label htmlFor="githubUrl">GitHub Profile</Label>
                                    <div className="relative">
                                        <Github className="absolute left-3 top-3 h-4 w-4 text-muted-foreground" />
                                        <Input
                                            id="githubUrl"
                                            placeholder="https://github.com/username"
                                            className="pl-10"
                                            {...register('githubUrl')}
                                        />
                                    </div>
                                    {errors.githubUrl && (
                                        <p className="text-sm text-destructive">
                                            {errors.githubUrl.message}
                                        </p>
                                    )}
                                </div>

                                <div className="space-y-2">
                                    <Label htmlFor="portfolioUrl">Portfolio URL</Label>
                                    <div className="relative">
                                        <Globe className="absolute left-3 top-3 h-4 w-4 text-muted-foreground" />
                                        <Input
                                            id="portfolioUrl"
                                            placeholder="https://yourportfolio.com"
                                            className="pl-10"
                                            {...register('portfolioUrl')}
                                        />
                                    </div>
                                    {errors.portfolioUrl && (
                                        <p className="text-sm text-destructive">
                                            {errors.portfolioUrl.message}
                                        </p>
                                    )}
                                </div>

                                <div className="space-y-2">
                                    <Label htmlFor="resumeUrl">Resume URL</Label>
                                    <div className="relative">
                                        <FileDown className="absolute left-3 top-3 h-4 w-4 text-muted-foreground" />
                                        <Input
                                            id="resumeUrl"
                                            placeholder="https://drive.google.com/file/d/..."
                                            className="pl-10"
                                            {...register('resumeUrl')}
                                        />
                                    </div>
                                    {errors.resumeUrl && (
                                        <p className="text-sm text-destructive">
                                            {errors.resumeUrl.message}
                                        </p>
                                    )}
                                    <p className="text-xs text-muted-foreground">
                                        Link to your resume (Google Drive, Dropbox, etc.)
                                    </p>
                                </div>
                            </div>
                        )}

                        {/* Navigation Buttons */}
                        <div className="flex items-center justify-between pt-6">
                            <Button
                                type="button"
                                variant="outline"
                                onClick={handleBack}
                                disabled={currentStep === 1 || completeMutation.isPending}
                            >
                                <ArrowLeft className="mr-2 h-4 w-4" />
                                Back
                            </Button>

                            {currentStep < 3 ? (
                                <Button type="button" onClick={handleNext}>
                                    Next
                                    <ArrowRight className="ml-2 h-4 w-4" />
                                </Button>
                            ) : (
                                <Button
                                    type="submit"
                                    disabled={completeMutation.isPending}
                                    className="min-w-[120px]"
                                >
                                    {completeMutation.isPending ? (
                                        <>
                                            <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                                            Saving...
                                        </>
                                    ) : (
                                        <>
                                            <CheckCircle className="mr-2 h-4 w-4" />
                                            Complete Setup
                                        </>
                                    )}
                                </Button>
                            )}
                        </div>
                    </CardContent>
                </form>
            </Card>
        </div>
    )
}
